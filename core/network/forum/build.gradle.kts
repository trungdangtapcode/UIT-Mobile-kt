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

tasks.register<JavaExec>("verifyForumMemberLab") {
    group = "verification"
    description = "Opt-in native member contracts against the isolated localhost Discourse lab."
    dependsOn(tasks.named("testClasses"))
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("vn.edu.uit.mobile.forum.network.ForumMemberLab")
    val credentials = providers.gradleProperty("forumLabCredentials")
    doFirst { args(credentials.get(), rootProject.layout.projectDirectory.file("docs/research/evidence/uit-forum-native-member-lab.json").asFile.absolutePath) }
}


tasks.register<JavaExec>("researchForumReadingUx") {
    group = "verification"
    description = "Manually check public ICPC/media topic reads using production Kotlin transport/parser."
    dependsOn(tasks.named("testClasses"))
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("vn.edu.uit.mobile.forum.network.ForumReaderUxResearch")
    val output = layout.buildDirectory.file("outputs/forum-reading-ux/public-network.json")
    doFirst { output.get().asFile.parentFile.mkdirs() }
    args(output.get().asFile.absolutePath)
}
