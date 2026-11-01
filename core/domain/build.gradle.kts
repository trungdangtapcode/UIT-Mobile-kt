plugins { id("uit.jvm"); `java-test-fixtures` }
dependencies {
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    api(project(":core:common"))
}

tasks.register<JavaExec>("exportWorkloadFigureData") {
    group = "documentation"
    description = "Export deterministic synthetic illustration data from WorkloadEngine."
    dependsOn(tasks.named("testClasses"))
    classpath = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    mainClass.set("vn.edu.uit.mobile.domain.WorkloadFigureData")
    args(rootProject.layout.projectDirectory.dir("docs/figures/workload").asFile.absolutePath)
}
